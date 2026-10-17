package io.nekohasekai.sagernet.fmt

import kotlin.test.Test
import kotlin.test.assertTrue

class ConfigBuilderTest {
    @Test
    fun routeOptionsTrackTheActiveAndroidInterface() {
        val route = buildRouteOptions()

        assertTrue(route.auto_detect_interface)
    }
}
