package com.yokhan.gates.app.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class BackupImportCoordinatorTest {
    @Test
    fun restoresSelectionAfterProfileOnlyPublicationFailure() {
        var selectedGroup = 2L
        var groups = listOf(1L, 2L)
        val failure = assertFailsWith<BackupImportFailure> {
            BackupImportCoordinator(
                prepare = { listOf(3L) },
                capture = { groups to selectedGroup },
                stopService = {},
                persist = { groups = it },
                rollback = { previous ->
                    groups = previous.first
                    selectedGroup = previous.second
                },
                publish = {
                    selectedGroup = it.first()
                    error("publication fixture failure")
                },
                restorePublished = {
                    if (selectedGroup !in groups) selectedGroup = groups.first()
                },
            ).execute()
        }
        assertEquals(true, failure.rollbackComplete)
        assertEquals(2L, selectedGroup)
    }

    @Test
    fun missingHopAndRuleProfilesAreRejected() {
        BackupImportValidator.requireProfileReferences(listOf(7, 8), setOf(7, 8))
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireProfileReferences(listOf(9), setOf(7, 8))
        }
    }

    @Test
    fun invalidPreparationDoesNotStopOrMutate() {
        var stopped = false
        var persisted = false
        val coordinator = BackupImportCoordinator(
            prepare = { throw InvalidBackupException() },
            capture = { Unit },
            stopService = { stopped = true },
            persist = { persisted = true },
            rollback = {},
        )

        assertFailsWith<InvalidBackupException> { coordinator.execute() }
        assertFalse(stopped)
        assertFalse(persisted)
    }

    @Test
    fun duplicateIdsAndMissingGroupReferencesAreRejected() {
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireSelectedSection(profile = false, rule = false, setting = false)
        }
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireUniquePositiveIds(listOf(Record(7), Record(7))) { it.id }
        }
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireUniquePositiveIds(listOf(Record(0))) { it.id }
        }
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireGroupReferences(
                profiles = listOf(Profile(10, 99)),
                groups = listOf(Record(4)),
                profileGroupId = { it.groupId },
                groupId = { it.id },
            )
        }
        assertFailsWith<InvalidBackupException> {
            BackupImportValidator.requireUniqueNonBlankKeys(listOf(Setting("same"), Setting("same"))) { it.key }
        }
    }

    @Test
    fun successfulImportPersistsBeforePublishingCache() {
        val events = mutableListOf<String>()
        val coordinator = BackupImportCoordinator(
            prepare = { events += "prepare"; "new" },
            capture = { events += "capture"; "old" },
            stopService = { events += "stop" },
            persist = { events += "persist:$it" },
            rollback = { events += "rollback:$it" },
            publish = { events += "publish:$it" },
        )

        assertEquals("new", coordinator.execute())
        assertEquals(listOf("prepare", "stop", "capture", "persist:new", "publish:new"), events)
    }

    @Test
    fun persistenceFailureRestoresRowsWithoutPublishingCache() {
        val events = mutableListOf<String>()
        val failure = assertFailsWith<BackupImportFailure> {
            BackupImportCoordinator(
                prepare = { "new" },
                capture = { "old" },
                stopService = {},
                persist = { throw IllegalStateException("write failed") },
                rollback = { events += "rows:$it" },
                publish = { events += "cache:$it" },
                restorePublished = { events += "cache:old" },
            ).execute()
        }

        assertTrue(failure.rollbackComplete)
        assertEquals(listOf("rows:old", "cache:old"), events)
    }

    @Test
    fun cachePublicationFailureRestoresRowsAndCache() {
        val events = mutableListOf<String>()
        val failure = assertFailsWith<BackupImportFailure> {
            BackupImportCoordinator(
                prepare = { "new" },
                capture = { "old" },
                stopService = {},
                persist = { events += "persist" },
                rollback = { events += "rows:$it" },
                publish = { throw IllegalStateException("cache failed") },
                restorePublished = { events += "cache:old" },
            ).execute()
        }

        assertTrue(failure.rollbackComplete)
        assertEquals(listOf("persist", "rows:old", "cache:old"), events)
    }

    @Test
    fun failedRollbackIsReportedAndCacheRestoreStillRuns() {
        var cacheRestored = false
        val failure = assertFailsWith<BackupImportFailure> {
            BackupImportCoordinator(
                prepare = { Unit },
                capture = { Unit },
                stopService = {},
                persist = {},
                rollback = { throw IllegalStateException("rollback failed") },
                publish = { throw IllegalStateException("cache failed") },
                restorePublished = { cacheRestored = true },
            ).execute()
        }

        assertFalse(failure.rollbackComplete)
        assertTrue(cacheRestored)
        assertIs<IllegalStateException>(failure.cause)
    }

    private data class Record(val id: Long)
    private data class Profile(val id: Long, val groupId: Long)
    private data class Setting(val key: String)
}
