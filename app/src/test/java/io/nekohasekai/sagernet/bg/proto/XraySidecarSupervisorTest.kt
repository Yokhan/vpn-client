package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.xray.XrayRouteSpec
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class XraySidecarSupervisorTest {

    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { dir ->
            dir.walkBottomUp().forEach { it.delete() }
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("xray-sidecar-test").toFile()
        .also { tempDirs += it }

    private fun spec() = XrayRouteSpec(
        routeId = "route-xhttp-test",
        sidecarPort = 15001,
        mappingPort = 15002,
        uuid = "00000000-0000-0000-0000-000000000000",
        serverName = "entry.example.invalid",
        realityPublicKey = "PUBLIC_KEY_PLACEHOLDER",
        realityShortId = "0123456789abcdef",
        path = "/xhttp-test",
        mode = "auto",
    )

    private class FakeProcess(var alive: Boolean, val id: Int) : XrayChildProcess {
        var terminated = 0
        var forced = 0

        override val pid: Long = id.toLong()
        override fun isAlive(): Boolean = alive
        override fun terminate(graceMillis: Long): Boolean {
            terminated += 1
            alive = false
            return true
        }

        override fun destroyForcibly() {
            forced += 1
            alive = false
        }
    }

    private class FakeLauncher(private val aliveAfterLaunch: Boolean = true) : XrayProcessLauncher {
        val commands = mutableListOf<List<String>>()
        val processes = mutableListOf<FakeProcess>()

        override fun launch(
            command: List<String>,
            workingDir: File?,
            onStdout: (String) -> Unit,
            onStderr: (String) -> Unit,
        ): XrayChildProcess {
            commands += command
            return FakeProcess(aliveAfterLaunch, processes.size + 1).also { processes += it }
        }
    }

    private fun readyProbe(result: Boolean): XrayReadinessProbe = object : XrayReadinessProbe {
        override fun awaitReady(
            host: String,
            port: Int,
            timeoutMillis: Long,
            pollMillis: Long,
            isAlive: () -> Boolean,
        ): Boolean = result && isAlive()
    }

    private fun supervisor(
        launcher: XrayProcessLauncher,
        probe: XrayReadinessProbe,
        logger: (String) -> Unit = {},
        policy: XrayRestartPolicy = XrayRestartPolicy(),
        clock: () -> Long = { 1_000L },
    ) = XraySidecarSupervisor(
        executable = File("/data/app/lib/libxray.so"),
        engineVersion = "v-test",
        launcher = launcher,
        readinessProbe = probe,
        logger = logger,
        clock = clock,
        sleeper = {},
        policy = policy,
    )

    @Test
    fun reportsReadyOnlyAfterTheLoopbackSocksPortAccepts() {
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(true))

        val result = sidecar.start(spec(), tempDir())

        val ready = assertIs<XrayStartResult.Ready>(result)
        assertEquals(15001, ready.socks.port)
        assertEquals("127.0.0.1", ready.socks.host)
        assertEquals(XraySidecarState.Running, sidecar.health().state)
        assertEquals(15001, sidecar.localSocks()?.port)
        assertEquals(
            listOf(File("/data/app/lib/libxray.so").absolutePath, "run", "-c", launcher.commands.single().last()),
            launcher.commands.single(),
        )
        assertFalse(sidecar.descriptor.ownsDatapath)
    }

    @Test
    fun readinessTimeoutRetriesOnceThenFailsClosed() {
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(false))

        val result = sidecar.start(spec(), tempDir())

        val rejected = assertIs<XrayStartResult.Rejected>(result)
        assertEquals(XrayFailureCode.NotReady, rejected.failure.code)
        assertEquals(2, launcher.processes.size)
        assertEquals(2, launcher.processes.count { it.terminated == 1 })
        assertEquals(XraySidecarState.Failed, sidecar.health().state)
        assertNull(sidecar.localSocks())
    }

    @Test
    fun fastExitRetriesOnceThenReportsCrash() {
        val launcher = FakeLauncher(aliveAfterLaunch = false)
        val sidecar = supervisor(launcher, readyProbe(true))

        val result = sidecar.start(spec(), tempDir())

        val rejected = assertIs<XrayStartResult.Rejected>(result)
        assertEquals(XrayFailureCode.Crashed, rejected.failure.code)
        assertEquals(2, launcher.processes.size)
        assertFalse(launcher.processes.any { it.terminated > 0 })
    }

    @Test
    fun stopTerminatesTheChildAndDeletesTheConfig() {
        val configDir = tempDir()
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(true))
        sidecar.start(spec(), configDir)
        val configFile = configDir.listFiles().orEmpty().single()
        assertTrue(configFile.exists())

        sidecar.stop(XrayStopReason.UserRequest)
        sidecar.stop(XrayStopReason.UserRequest)

        assertFalse(configFile.exists())
        assertEquals(1, launcher.processes.single().terminated)
        assertEquals(XraySidecarState.Stopped, sidecar.health().state)
        assertNull(sidecar.localSocks())
    }

    @Test
    fun crashRecoveryIsBoundedAndFailsAfterTheBudget() {
        var now = 1_000L
        val launcher = FakeLauncher()
        val policy = XrayRestartPolicy(maxRestartsPerWindow = 2, restartWindowMillis = 60_000)
        val sidecar = supervisor(launcher, readyProbe(true), policy = policy, clock = { now })
        sidecar.start(spec(), tempDir())
        val first = launcher.processes.single()

        first.alive = false
        now += 1_000
        sidecar.tick(now)
        assertEquals(2, launcher.processes.size)
        assertEquals(1, sidecar.health().restarts)
        assertEquals(XraySidecarState.Running, sidecar.health().state)

        launcher.processes.last().alive = false
        now += 2_000
        sidecar.tick(now)
        assertEquals(3, launcher.processes.size)
        assertEquals(2, sidecar.health().restarts)

        launcher.processes.last().alive = false
        now += 4_000
        sidecar.tick(now)
        assertEquals(XraySidecarState.Failed, sidecar.health().state)
        assertEquals(XrayFailureCode.Crashed, sidecar.health().lastFailure?.code)
        assertNull(sidecar.localSocks())
    }

    @Test
    fun logsContainPathsAndStatesButNeverSecrets() {
        val lines = mutableListOf<String>()
        val spec = spec()
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(true), logger = { lines += it })

        sidecar.start(spec, tempDir())
        sidecar.stop(XrayStopReason.UserRequest)

        val text = lines.joinToString("\n")
        assertTrue(text.contains("[xray]"))
        assertTrue(text.contains("libxray.so"))
        assertFalse(text.contains(spec.uuid))
        assertFalse(text.contains(spec.serverName))
        assertFalse(text.contains(spec.realityPublicKey))
        assertFalse(text.contains(spec.realityShortId))
        assertFalse(text.contains(spec.path))
    }

    @Test
    fun startIsIdempotentWhileRunning() {
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(true))
        val dir = tempDir()

        val first = assertIs<XrayStartResult.Ready>(sidecar.start(spec(), dir))
        val second = assertIs<XrayStartResult.Ready>(sidecar.start(spec(), dir))

        assertEquals(first.epoch, second.epoch)
        assertEquals(1, launcher.processes.size)
    }

    @Test
    fun versionDoesNotStartTheEngine() {
        val launcher = FakeLauncher()
        val sidecar = supervisor(launcher, readyProbe(true))

        val version = sidecar.version()

        assertEquals("xray", version.engine)
        assertEquals("v-test", version.engineVersion)
        assertTrue(launcher.processes.isEmpty())
    }
}
