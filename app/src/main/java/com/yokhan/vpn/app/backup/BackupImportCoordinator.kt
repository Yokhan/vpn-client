package com.yokhan.gates.app.backup

internal class InvalidBackupException : IllegalArgumentException("Invalid backup file.")

internal class BackupImportFailure(
    val rollbackComplete: Boolean,
    cause: Throwable,
) : IllegalStateException(
    if (rollbackComplete) {
        "Import failed. Previous data was restored."
    } else {
        "Import failed and restoration was incomplete. Verify local data before using it."
    },
    cause,
)

internal class BackupImportCoordinator<Prepared, Snapshot>(
    private val prepare: () -> Prepared,
    private val capture: () -> Snapshot,
    private val stopService: () -> Unit,
    private val persist: (Prepared) -> Unit,
    private val rollback: (Snapshot) -> Unit,
    private val publish: (Prepared) -> Unit = {},
    private val restorePublished: () -> Unit = {},
) {
    fun execute(): Prepared {
        val prepared = prepare()
        stopService()
        val snapshot = capture()
        try {
            persist(prepared)
            publish(prepared)
        } catch (failure: Exception) {
            var rollbackFailure: Exception? = null
            try {
                rollback(snapshot)
            } catch (error: Exception) {
                rollbackFailure = error
            }
            // Database invalidation can refresh caches before explicit publication.
            try {
                restorePublished()
            } catch (error: Exception) {
                if (rollbackFailure == null) rollbackFailure = error else rollbackFailure.addSuppressed(error)
            }

            rollbackFailure?.let(failure::addSuppressed)
            throw BackupImportFailure(rollbackFailure == null, failure)
        }
        return prepared
    }
}

internal object BackupImportValidator {
    fun requireProfileReferences(references: List<Long>, profileIds: Set<Long>) {
        if (references.any { it !in profileIds }) throw InvalidBackupException()
    }

    fun requireSelectedSection(profile: Boolean, rule: Boolean, setting: Boolean) {
        if (!profile && !rule && !setting) throw InvalidBackupException()
    }

    fun <T> requireUniquePositiveIds(records: List<T>, id: (T) -> Long) {
        val seen = HashSet<Long>(records.size)
        if (records.any { id(it) <= 0L || !seen.add(id(it)) }) throw InvalidBackupException()
    }

    fun <T> requireUniqueNonBlankKeys(records: List<T>, key: (T) -> String) {
        val seen = HashSet<String>(records.size)
        if (records.any { key(it).isBlank() || !seen.add(key(it)) }) throw InvalidBackupException()
    }

    fun <Profile, Group> requireGroupReferences(
        profiles: List<Profile>,
        groups: List<Group>,
        profileGroupId: (Profile) -> Long,
        groupId: (Group) -> Long,
    ) {
        val groupIds = groups.mapTo(HashSet(groups.size), groupId)
        if (profiles.any { profileGroupId(it) !in groupIds }) throw InvalidBackupException()
    }
}
