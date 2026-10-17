package com.yokhan.gates.app.routing

import android.content.Context
import com.yokhan.vpn.shared.domain.SmartRouteCandidate
import com.yokhan.vpn.shared.domain.SmartRouteContext
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class YokhanRouteObservationScope(
    val coarseNetworkBucket: String,
    val manifestFingerprint: String,
) {
    init {
        require(coarseNetworkBucket.isNotBlank())
        require(manifestFingerprint.isNotBlank())
    }

    fun storageKey(): String = sha256(
        listOf(coarseNetworkBucket.normalizedScopePart(), manifestFingerprint.normalizedScopePart())
            .joinToString(SCOPE_PART_SEPARATOR),
    )
}

data class YokhanRouteLocalObservation(
    val routeId: String,
    val localScore: Int?,
    val successfulProbeCount: Int,
    val recordedAtEpochSeconds: Long,
    val quarantineUntilEpochSeconds: Long,
)

data class YokhanRouteLocalObservationState(
    val previousKnownGoodRouteId: String = "",
    val previousKnownGoodRecordedAtEpochSeconds: Long = 0,
    val lastSwitchAtEpochSeconds: Long = 0,
    val observations: Map<String, YokhanRouteLocalObservation> = emptyMap(),
) {
    fun toSmartRouteCandidates(
        candidates: List<SmartRouteCandidate>,
        nowEpochSeconds: Long,
    ): List<SmartRouteCandidate> = candidates.map { candidate ->
        observationFor(candidate.routeId, nowEpochSeconds)?.let { observation ->
            candidate.copy(
                localScore = observation.localScore,
                successfulProbeCount = observation.successfulProbeCount,
                quarantineUntilEpochSeconds = observation.quarantineUntilEpochSeconds,
            )
        } ?: candidate
    }

    fun toSmartRouteContext(
        nowEpochSeconds: Long,
        currentRouteId: String = "",
        switchCooldownSeconds: Long = DEFAULT_SWITCH_COOLDOWN_SECONDS,
        minimumSwitchImprovement: Int = DEFAULT_SWITCH_IMPROVEMENT,
    ): SmartRouteContext {
        val previousKnownGood = previousKnownGood(nowEpochSeconds)
        return SmartRouteContext(
            nowEpochSeconds = nowEpochSeconds,
            currentRouteId = currentRouteId.normalizedRouteId(),
            previousKnownGoodRouteId = previousKnownGood?.routeId.orEmpty(),
            previousKnownGoodRecordedAt = previousKnownGood?.recordedAtEpochSeconds ?: 0,
            previousKnownGoodMaxAgeSeconds = LOCAL_OBSERVATION_TTL_SECONDS,
            lastSwitchAt = lastSwitchAtEpochSeconds,
            switchCooldownSeconds = switchCooldownSeconds.coerceAtLeast(0),
            minimumSwitchImprovement = minimumSwitchImprovement.coerceAtLeast(0),
        )
    }

    fun recordConnectionSuccess(
        routeId: String,
        localScore: Int,
        nowEpochSeconds: Long,
    ): YokhanRouteLocalObservationState {
        val normalizedRouteId = routeId.requireNormalizedRouteId()
        val previous = observations[normalizedRouteId]
        val observation = YokhanRouteLocalObservation(
            routeId = normalizedRouteId,
            localScore = localScore.coerceIn(MINIMUM_SCORE, MAXIMUM_SCORE),
            successfulProbeCount = (previous?.successfulProbeCount ?: 0) + SUCCESSFUL_PROBE_INCREMENT,
            recordedAtEpochSeconds = nowEpochSeconds,
            quarantineUntilEpochSeconds = NO_QUARANTINE,
        )
        return copy(
            previousKnownGoodRouteId = normalizedRouteId,
            previousKnownGoodRecordedAtEpochSeconds = nowEpochSeconds,
            observations = observations + (normalizedRouteId to observation),
        )
    }

    fun recordQuarantine(
        routeId: String,
        quarantineUntilEpochSeconds: Long,
        nowEpochSeconds: Long,
        localScore: Int? = null,
    ): YokhanRouteLocalObservationState {
        val normalizedRouteId = routeId.requireNormalizedRouteId()
        val previous = observations[normalizedRouteId]
        val observation = YokhanRouteLocalObservation(
            routeId = normalizedRouteId,
            localScore = localScore?.coerceIn(MINIMUM_SCORE, MAXIMUM_SCORE) ?: previous?.localScore,
            successfulProbeCount = NO_SUCCESSFUL_PROBES,
            recordedAtEpochSeconds = nowEpochSeconds,
            quarantineUntilEpochSeconds = quarantineUntilEpochSeconds.coerceAtLeast(nowEpochSeconds),
        )
        return copy(observations = observations + (normalizedRouteId to observation))
    }

    fun recordRouteSwitch(nowEpochSeconds: Long): YokhanRouteLocalObservationState =
        copy(lastSwitchAtEpochSeconds = nowEpochSeconds)

    fun pruneExpired(nowEpochSeconds: Long): YokhanRouteLocalObservationState {
        val activeObservations = observations.filterValues { observation ->
            observation.recordedAtEpochSeconds.isFreshAt(nowEpochSeconds)
        }
        val previousKnownGood = previousKnownGood(nowEpochSeconds)
        val activeSwitchAt = lastSwitchAtEpochSeconds.takeIf {
            it.isFreshAt(nowEpochSeconds)
        } ?: NO_TIMESTAMP
        return copy(
            previousKnownGoodRouteId = previousKnownGood?.routeId.orEmpty(),
            previousKnownGoodRecordedAtEpochSeconds = previousKnownGood?.recordedAtEpochSeconds ?: NO_TIMESTAMP,
            lastSwitchAtEpochSeconds = activeSwitchAt,
            observations = activeObservations,
        )
    }

    private fun observationFor(
        routeId: String,
        nowEpochSeconds: Long,
    ): YokhanRouteLocalObservation? = observations[routeId.normalizedRouteId()]
        ?.takeIf { observation -> observation.recordedAtEpochSeconds.isFreshAt(nowEpochSeconds) }

    private fun previousKnownGood(nowEpochSeconds: Long): YokhanRouteLocalObservation? {
        if (!previousKnownGoodRecordedAtEpochSeconds.isFreshAt(nowEpochSeconds)) return null
        return YokhanRouteLocalObservation(
            routeId = previousKnownGoodRouteId,
            localScore = null,
            successfulProbeCount = NO_SUCCESSFUL_PROBES,
            recordedAtEpochSeconds = previousKnownGoodRecordedAtEpochSeconds,
            quarantineUntilEpochSeconds = NO_QUARANTINE,
        ).takeIf { it.routeId.isNotBlank() }
    }
}

class YokhanRouteLocalObservationStore(context: Context) {

    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun load(
        scope: YokhanRouteObservationScope,
        nowEpochSeconds: Long,
    ): YokhanRouteLocalObservationState {
        val rawState = preferences.getString(scope.storageKey(), null) ?: return YokhanRouteLocalObservationState()
        val state = decode(rawState) ?: return YokhanRouteLocalObservationState()
        val prunedState = state.pruneExpired(nowEpochSeconds)
        if (prunedState != state) save(scope, prunedState)
        return prunedState
    }

    fun recordConnectionSuccess(
        scope: YokhanRouteObservationScope,
        routeId: String,
        localScore: Int,
        nowEpochSeconds: Long,
    ): YokhanRouteLocalObservationState = update(scope, nowEpochSeconds) { state ->
        state.recordConnectionSuccess(routeId, localScore, nowEpochSeconds)
    }

    fun recordQuarantine(
        scope: YokhanRouteObservationScope,
        routeId: String,
        quarantineUntilEpochSeconds: Long,
        nowEpochSeconds: Long,
        localScore: Int? = null,
    ): YokhanRouteLocalObservationState = update(scope, nowEpochSeconds) { state ->
        state.recordQuarantine(routeId, quarantineUntilEpochSeconds, nowEpochSeconds, localScore)
    }

    fun recordRouteSwitch(
        scope: YokhanRouteObservationScope,
        nowEpochSeconds: Long,
    ): YokhanRouteLocalObservationState = update(scope, nowEpochSeconds) { state ->
        state.recordRouteSwitch(nowEpochSeconds)
    }

    private fun update(
        scope: YokhanRouteObservationScope,
        nowEpochSeconds: Long,
        transform: (YokhanRouteLocalObservationState) -> YokhanRouteLocalObservationState,
    ): YokhanRouteLocalObservationState {
        val updatedState = transform(load(scope, nowEpochSeconds))
        save(scope, updatedState)
        return updatedState
    }

    private fun save(
        scope: YokhanRouteObservationScope,
        state: YokhanRouteLocalObservationState,
    ) {
        preferences.edit().putString(scope.storageKey(), encode(state)).apply()
    }

    private fun encode(state: YokhanRouteLocalObservationState): String = JSONObject()
        .put(KEY_PREVIOUS_ROUTE_ID, state.previousKnownGoodRouteId)
        .put(KEY_PREVIOUS_RECORDED_AT, state.previousKnownGoodRecordedAtEpochSeconds)
        .put(KEY_LAST_SWITCH_AT, state.lastSwitchAtEpochSeconds)
        .put(KEY_OBSERVATIONS, observationsJson(state.observations.values))
        .toString()

    private fun observationsJson(observations: Collection<YokhanRouteLocalObservation>): JSONArray = JSONArray().apply {
        observations.forEach { observation ->
            put(
                JSONObject()
                    .put(KEY_ROUTE_ID, observation.routeId)
                    .putOpt(KEY_LOCAL_SCORE, observation.localScore)
                    .put(KEY_SUCCESSFUL_PROBE_COUNT, observation.successfulProbeCount)
                    .put(KEY_RECORDED_AT, observation.recordedAtEpochSeconds)
                    .put(KEY_QUARANTINE_UNTIL, observation.quarantineUntilEpochSeconds),
            )
        }
    }

    private fun decode(rawState: String): YokhanRouteLocalObservationState? = runCatching {
        val root = JSONObject(rawState)
        YokhanRouteLocalObservationState(
            previousKnownGoodRouteId = root.optString(KEY_PREVIOUS_ROUTE_ID).normalizedRouteId(),
            previousKnownGoodRecordedAtEpochSeconds = root.optLong(KEY_PREVIOUS_RECORDED_AT),
            lastSwitchAtEpochSeconds = root.optLong(KEY_LAST_SWITCH_AT),
            observations = parseObservations(root.optJSONArray(KEY_OBSERVATIONS)),
        )
    }.getOrNull()

    private fun parseObservations(value: JSONArray?): Map<String, YokhanRouteLocalObservation> {
        if (value == null) return emptyMap()
        return (0 until value.length()).mapNotNull { index ->
            value.optJSONObject(index)?.toObservation()
        }.associateBy(YokhanRouteLocalObservation::routeId)
    }

    private fun JSONObject.toObservation(): YokhanRouteLocalObservation? {
        val routeId = optString(KEY_ROUTE_ID).normalizedRouteId()
        if (routeId.isBlank()) return null
        return YokhanRouteLocalObservation(
            routeId = routeId,
            localScore = takeIf { has(KEY_LOCAL_SCORE) }?.optInt(KEY_LOCAL_SCORE)
                ?.coerceIn(MINIMUM_SCORE, MAXIMUM_SCORE),
            successfulProbeCount = optInt(KEY_SUCCESSFUL_PROBE_COUNT).coerceAtLeast(NO_SUCCESSFUL_PROBES),
            recordedAtEpochSeconds = optLong(KEY_RECORDED_AT),
            quarantineUntilEpochSeconds = optLong(KEY_QUARANTINE_UNTIL),
        )
    }
}

private fun String.normalizedScopePart(): String = trim().lowercase()

private fun String.normalizedRouteId(): String = trim().lowercase()

private fun String.requireNormalizedRouteId(): String = normalizedRouteId().also { routeId ->
    require(routeId.isNotBlank())
}

private fun Long.isFreshAt(nowEpochSeconds: Long): Boolean = this > NO_TIMESTAMP &&
    nowEpochSeconds - this in ZERO_SECONDS until LOCAL_OBSERVATION_TTL_SECONDS

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and BYTE_MASK) }

private const val PREFERENCES_NAME = "yokhan_route_local_observations"
private const val SCOPE_PART_SEPARATOR = "\u0000"
private const val KEY_PREVIOUS_ROUTE_ID = "previous_route_id"
private const val KEY_PREVIOUS_RECORDED_AT = "previous_recorded_at"
private const val KEY_LAST_SWITCH_AT = "last_switch_at"
private const val KEY_OBSERVATIONS = "observations"
private const val KEY_ROUTE_ID = "route_id"
private const val KEY_LOCAL_SCORE = "local_score"
private const val KEY_SUCCESSFUL_PROBE_COUNT = "successful_probe_count"
private const val KEY_RECORDED_AT = "recorded_at"
private const val KEY_QUARANTINE_UNTIL = "quarantine_until"
private const val MINIMUM_SCORE = 0
private const val MAXIMUM_SCORE = 100
private const val SUCCESSFUL_PROBE_INCREMENT = 1
private const val NO_SUCCESSFUL_PROBES = 0
private const val NO_QUARANTINE = 0L
private const val NO_TIMESTAMP = 0L
private const val ZERO_SECONDS = 0L
private const val BYTE_MASK = 0xff
private const val DEFAULT_SWITCH_COOLDOWN_SECONDS = 10L * 60L
private const val DEFAULT_SWITCH_IMPROVEMENT = 15
const val LOCAL_OBSERVATION_TTL_SECONDS = 24L * 60L * 60L
