package com.yokhan.gates.app.auth

internal data class ProfileRefreshPlan(
    val replacementIds: List<Long?>,
    val removedIds: List<Long>,
)

internal fun initialImportedProfile(
    selectedId: Long,
    currentId: Long,
    tunnelRunning: Boolean,
    importedIds: List<Long>,
): Long? = importedIds.firstOrNull().takeIf { selectedId == 0L && currentId == 0L && !tunnelRunning }

// A refresh changes saved configurations, not the user's selected/running tunnel.
internal fun planProfileRefresh(
    existing: List<Pair<Long, String>>,
    incomingKeys: List<String>,
    retainedIds: Set<Long>,
): ProfileRefreshPlan {
    val available = existing.groupBy({ it.second }, { it.first })
        .mapValues { (_, ids) -> ArrayDeque(ids) }
    val replacements = incomingKeys.map { available[it]?.removeFirstOrNull() }
    val keptIds = replacements.filterNotNull().toSet() + retainedIds
    return ProfileRefreshPlan(replacements, existing.map { it.first }.filterNot { it in keptIds })
}
