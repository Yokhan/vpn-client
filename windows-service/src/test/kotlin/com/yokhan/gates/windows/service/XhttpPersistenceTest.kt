package com.yokhan.gates.windows.service

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class XhttpPersistenceTest {

    @Test
    fun roundTripsTheSidecarSpecSeparatelyFromTheSingBoxJson() {
        val directory = Files.createTempDirectory("gates-xhttp-persist-")
        try {
            val paths = WindowsServicePaths(
                launcherExecutable = directory.resolve("GATES.exe"),
                runtimeExecutable = directory.resolve("sing-box.exe"),
                dataDirectory = directory,
                configFile = directory.resolve("active.json"),
                logFile = directory.resolve("service.log"),
                controlTokenFile = directory.resolve("control.token"),
            )
            val validated = assertIs<ConfigValidationResult.Accepted>(
                SingBoxConfigValidator.validate(config().toByteArray()),
            ).config
            val launcher = SingBoxProcessLauncher(paths)

            launcher.persistConfig(validated)
            assertEquals(validated, launcher.loadPersistedConfig())
            assertEquals("xhttp", launcher.loadPersistedConfig()?.sidecar?.outboundTag)
            assertFalse(Files.readString(paths.configFile).contains("\"dns\""))

            val runtime = runtimeConfigJson(validated, null)
            assertFalse(runtime.contains("gates_xhttp"))
            assertFalse(runtime.contains("xhttpSettings"))
            assertNotNull(validated.sidecar)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
            }
        }
    }

    private fun config(): String {
        // Synthetic RFC 4122 documentation-style value, not a credential.
        val uuid = "00000000-0000-4000-8000-000000000001"
        return """{"inbounds":[{"type":"tun","tag":"tun-in"},
            {"type":"mixed","tag":"mixed-in","listen":"127.0.0.1","listen_port":2088}],
            "outbounds":[
              {"type":"socks","tag":"xhttp","server":"127.0.0.1","server_port":2095,"version":"5"},
              {"type":"direct","tag":"direct"}],
            "route":{"final":"xhttp"},
            "gates_xhttp":{"schema":1,"outbound_tag":"xhttp","executable":"xray.exe",
              "executable_sha256":"${"a".repeat(64)}","listen_port":2095,"required":true,
              "xray_config":{
                "log":{"loglevel":"warning"},
                "inbounds":[{"protocol":"socks","listen":"127.0.0.1","port":2095,
                  "settings":{"auth":"noauth","udp":true}}],
                "outbounds":[{"protocol":"vless",
                  "settings":{"vnext":[{"address":"203.0.113.10","port":443,
                    "users":[{"id":"$uuid","encryption":"none"}]}]},
                  "streamSettings":{"network":"xhttp","security":"reality",
                    "realitySettings":{"serverName":"entry.example.com","fingerprint":"chrome",
                      "publicKey":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","shortId":"abcd"},
                    "xhttpSettings":{"path":"/lab","host":"entry.example.com","mode":"auto"},
                    "sockopt":{"interface":"Wi-Fi"}}}]}}}""".trimIndent()
    }
}
