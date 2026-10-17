package com.yokhan.gates.app.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfileRefreshPlanTest {
    @Test
    fun reorderAndRepeatKeepProfileIdentity() {
        val existing = listOf(10L to "HY", 20L to "TCP")
        assertEquals(ProfileRefreshPlan(listOf(20L, 10L), emptyList()),
            planProfileRefresh(existing, listOf("TCP", "HY"), setOf(10L)))
        assertEquals(ProfileRefreshPlan(listOf(10L, 20L), emptyList()),
            planProfileRefresh(existing, listOf("HY", "TCP"), emptySet()))
    }

    @Test
    fun missingSelectedAndLastRunningProfilesAreRetained() {
        assertEquals(ProfileRefreshPlan(listOf(null), listOf(30L)),
            planProfileRefresh(listOf(10L to "HY", 20L to "TCP", 30L to "Old"),
                listOf("New"), setOf(10L, 20L)))
    }

    @Test
    fun firstImportOnlyCreatesProfiles() {
        assertEquals(ProfileRefreshPlan(listOf(null, null), emptyList()),
            planProfileRefresh(emptyList(), listOf("TCP", "HY"), emptySet()))
        assertEquals(10L, initialImportedProfile(0L, 0L, false, listOf(10L, 20L)))
        assertNull(initialImportedProfile(10L, 10L, false, listOf(20L)))
        assertNull(initialImportedProfile(0L, 10L, false, listOf(20L)))
        assertNull(initialImportedProfile(0L, 0L, true, listOf(20L)))
    }

    @Test
    fun duplicateNamesUseDistinctStableIds() {
        assertEquals(ProfileRefreshPlan(listOf(10L, 20L, null), emptyList()),
            planProfileRefresh(listOf(10L to "HY", 20L to "HY"),
                listOf("HY", "HY", "HY"), emptySet()))
    }
}
