package com.yokhan.gates.windows

import java.awt.Point
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsDropIntakeTest {
    @Test
    fun singleJsonFileIsClassifiedAsProfileImport() {
        val dir = Files.createTempDirectory("gates-drop-profile-").toFile()
        val profile = dir.resolve("my-profile.json").apply { writeText("{}") }

        val action = classifyDroppedFiles(listOf(profile))
        assertIs<DropIntakeAction.ImportProfileFile>(action)
        assertEquals(profile.absolutePath, action.file.absolutePath)
    }

    @Test
    fun droppedExeShortcutAndFolderResolveDeduplicateAndSkipUninstallers() {
        val root = Files.createTempDirectory("gates-drop-mixed-").toFile()
        val appDir = root.resolve("SteamApp").apply { mkdirs() }
        val gameExe = appDir.resolve("game.exe").apply { writeBytes(byteArrayOf(1)) }
        val launcherExe = root.resolve("launcher.exe").apply { writeBytes(byteArrayOf(1)) }
        appDir.resolve("unins000.exe").writeBytes(byteArrayOf(1))
        appDir.resolve("setup.exe").writeBytes(byteArrayOf(1))
        appDir.resolve("vc_redist.x64.exe").writeBytes(byteArrayOf(1))
        appDir.resolve("UnityCrashHandler64.exe").writeBytes(byteArrayOf(1))
        val shortcut = root.resolve("Game.lnk").apply { writeBytes(byteArrayOf(2)) }

        var batchCalls = 0
        val action = classifyDroppedFiles(listOf(appDir, launcherExe, shortcut)) { paths ->
            batchCalls += 1
            assertEquals(listOf(shortcut.absolutePath), paths)
            mapOf(shortcut.absolutePath to gameExe.absolutePath)
        }

        assertEquals(1, batchCalls)
        assertIs<DropIntakeAction.RouteExecutables>(action)
        assertEquals(listOf(gameExe.absolutePath, launcherExe.absolutePath), action.exePaths)
        assertEquals(4, action.skippedCount)
    }

    @Test
    fun batchAddAndOneClickMoveUpdateRulesCleanly() {
        val root = Files.createTempDirectory("gates-batch-move-").toFile()
        val controller = WindowsRuntimeController(
            allowRuntimeServiceDelegation = false,
            appDir = root,
            validateGeneratedConfig = { RuntimeCommandResult(0, "ok") },
        )
        controller.writeConfig(
            WindowsAppConfig(
                activeRoute = "bridge",
                physicalBindEnabled = false,
                restoreSystemProxyOnStop = false,
                roomNetEnabled = false,
            ),
        )

        val addResult = controller.addAppsRouting(
            targets = listOf("game.exe", "discord.exe"),
            proxy = true,
        )
        assertEquals(0, addResult.exitCode, addResult.output)
        assertEquals(listOf("game.exe", "discord.exe"), controller.readConfig().appProxyProcessNames)

        val moveResult = controller.moveAppRouting("game.exe", toProxy = false)
        assertEquals(0, moveResult.exitCode, moveResult.output)
        val afterMove = controller.readConfig()
        assertFalse("game.exe" in afterMove.appProxyProcessNames)
        assertTrue("discord.exe" in afterMove.appProxyProcessNames)
        assertTrue("game.exe" in afterMove.appDirectProcessNames)
    }

    @Test
    fun binaryLnkParserReadsUnicodeTargetArgumentsAndWorkingDir() {
        val bytes = buildTestLnkBytes(
            localBasePath = "C:\\Users\\demo\\AppData\\Local\\Discord\\Update.exe",
            workingDir = "C:\\Users\\demo\\AppData\\Local\\Discord\\app-1.0.9001",
            arguments = "--processStart Discord.exe",
        )
        val parsed = parseWindowsShortcutBytes(bytes)
        assertNotNull(parsed)
        assertEquals("C:\\Users\\demo\\AppData\\Local\\Discord\\Update.exe", parsed.targetPath)
        assertEquals("--processStart Discord.exe", parsed.arguments)
        assertEquals("C:\\Users\\demo\\AppData\\Local\\Discord\\app-1.0.9001", parsed.workingDirectory)
    }

    @Test
    fun squirrelUpdateExeResolvesNewestVersionedAppExecutable() {
        val root = Files.createTempDirectory("gates-squirrel-").toFile()
        val oldDir = root.resolve("app-1.0.9000").apply { mkdirs() }
        oldDir.resolve("Discord.exe").writeBytes(byteArrayOf(1))
        val newDir = root.resolve("app-1.0.9002").apply { mkdirs() }
        val newestExe = newDir.resolve("Discord.exe").apply { writeBytes(byteArrayOf(1)) }
        val updateExe = root.resolve("Update.exe").apply { writeBytes(byteArrayOf(1)) }

        val resolved = resolveSquirrelOrDirectTarget(
            WindowsResolvedShortcut(
                targetPath = updateExe.absolutePath,
                arguments = "--processStart Discord.exe",
                workingDirectory = oldDir.absolutePath,
            ),
        )
        assertEquals(newestExe.absolutePath, resolved)
    }

    @Test
    fun depthFourFolderAndUrlDropAndHidpiMappingWorkAsExpected() {
        val root = Files.createTempDirectory("gates-depth4-").toFile()
        val nested = root.resolve("Games/Company/Title/bin").apply { mkdirs() }
        val exe = nested.resolve("mygame.exe").apply { writeBytes(byteArrayOf(1)) }
        val action = classifyDroppedFiles(listOf(root))
        assertIs<DropIntakeAction.RouteExecutables>(action)
        assertEquals(listOf(exe.absolutePath), action.exePaths)

        val urlFile = root.resolve("Game.url").apply { writeText("[InternetShortcut]\nURL=steam://rungameid/730") }
        val urlAction = classifyDroppedFiles(listOf(urlFile))
        assertIs<DropIntakeAction.Unsupported>(urlAction)
        assertTrue(urlAction.reason.contains(".url"))

        val mapped = mapAwtPointToComposeRootPx(
            pointInWindow = Point(210, 140),
            contentPaneOriginInWindow = Point(10, 40),
            density = 1.5f,
        )
        assertEquals(300f, mapped.x)
        assertEquals(150f, mapped.y)
    }

    private fun buildTestLnkBytes(
        localBasePath: String,
        workingDir: String,
        arguments: String,
    ): ByteArray {
        val hasWorkingDir = 0x10
        val hasArguments = 0x20
        val hasLinkInfo = 0x02
        val isUnicode = 0x80
        val flags = hasLinkInfo or hasWorkingDir or hasArguments or isUnicode

        val unicodePathBytes = (localBasePath + "\u0000").toByteArray(StandardCharsets.UTF_16LE)
        val headerSize = 0x24
        val ansiOffset = headerSize + unicodePathBytes.size
        val linkInfoSize = ansiOffset + 1 // +1 byte empty ANSI fallback
        val linkInfo = ByteBuffer.allocate(linkInfoSize).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, linkInfoSize)
            putInt(4, headerSize)
            putInt(8, 1) // VolumeIDAndLocalBasePath
            putInt(12, 0)
            putInt(16, ansiOffset)
            putInt(20, 0)
            putInt(24, ansiOffset)
            putInt(28, headerSize) // LocalBasePathOffsetUnicode
            putInt(32, ansiOffset) // CommonPathSuffixOffsetUnicode
            position(headerSize)
            put(unicodePathBytes)
            put(ansiOffset, 0)
        }.array()

        val out = ByteArrayOutputStream()
        val header = ByteBuffer.allocate(76).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x4C)
            putInt(20, flags)
        }.array()
        out.write(header)
        out.write(linkInfo)

        fun writeUnicodeStringData(text: String) {
            val lenBuf = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(text.length.toShort()).array()
            out.write(lenBuf)
            out.write(text.toByteArray(StandardCharsets.UTF_16LE))
        }
        writeUnicodeStringData(workingDir)
        writeUnicodeStringData(arguments)
        return out.toByteArray()
    }
}
