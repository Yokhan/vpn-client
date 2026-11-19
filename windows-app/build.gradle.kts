import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.language.jvm.tasks.ProcessResources
import org.gradle.api.tasks.Sync
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.net.URI
import java.security.MessageDigest

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared-domain"))
    implementation(project(":shared-ui"))
    implementation(project(":windows-service"))
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("net.java.dev.jna:jna-platform:5.19.0")
    testImplementation(kotlin("test"))
}

val defaultRuntimeDir = rootProject.projectDir.parentFile
    .resolve("YokhanIndependentVPN")
    .resolve(".tmp")
    .resolve("sing-box")
    .resolve("sing-box-1.13.12-windows-amd64")

val windowsRuntimeDir = providers.environmentVariable("YOKHAN_SING_BOX_DIR")
    .map { file(it) }
    .orElse(defaultRuntimeDir)
val windowsPackageVersion = "1.9.13"
val windowsUpdateSignerSha1 = providers.environmentVariable("GATES_WINDOWS_UPDATE_SIGNER_SHA1").orElse("")
val winswVersion = "2.12.0"
val winswSha256 = "05b82d46ad331cc16bdc00de5c6332c1ef818df8ceefcd49c726553209b3a0da"
val winswFile = layout.buildDirectory.file("winsw/Gates.Service.exe")
val serviceFatJarFile = rootProject.layout.projectDirectory
    .file("windows-service/build/libs/windows-service-all.jar")
val nativeAppResourcesRoot = layout.buildDirectory.dir("generated/native-app-resources")
val pinnedRuntimeHashes = mapOf(
    "sing-box.exe" to "64b1dfaed6fa758295233fd0bec8b32cf2115f29773adbf38e0f026c3c7986f2",
    "libcronet.dll" to "c7434cfa93c3041321dd19111c4de6c52b8a9531a65661ba45425d3c51ec69e2",
    "LICENSE" to "bb3805862b583aee73ad6f7805ec634747a37257a637a3069857843f05ea589c",
    "xray.exe" to "d5b558191cccb1e92a5772b68b8cab67717b26f634b0f15153215f65ae982a7e",
)

val verifyPinnedWindowsRuntime by tasks.registering {
    val runtimeFiles = pinnedRuntimeHashes.keys.map { name -> windowsRuntimeDir.map { File(it, name) } }
    inputs.files(runtimeFiles)
    doLast {
        pinnedRuntimeHashes.forEach { (name, expectedHash) ->
            val file = File(windowsRuntimeDir.get(), name)
            check(file.isFile) { "Pinned Windows runtime file is missing: $name" }
            val actualHash = MessageDigest.getInstance("SHA-256")
                .digest(file.readBytes())
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
            check(actualHash == expectedHash) { "Pinned Windows runtime SHA-256 mismatch: $name" }
        }
        val xrayPinFile = File(windowsRuntimeDir.get(), "xray.sha256")
        check(xrayPinFile.isFile) { "Pinned Windows runtime file is missing: xray.sha256" }
        val xrayPin = xrayPinFile.readText(Charsets.US_ASCII).trim().lowercase()
        check(xrayPin == pinnedRuntimeHashes.getValue("xray.exe")) {
            "xray.sha256 must contain the pinned xray.exe SHA-256"
        }
    }
}

val downloadPinnedWinSw by tasks.registering {
    outputs.file(winswFile)
    doLast {
        val target = winswFile.get().asFile
        target.parentFile.mkdirs()
        if (!target.isFile) {
            URI("https://github.com/winsw/winsw/releases/download/v$winswVersion/WinSW-x64.exe")
                .toURL()
                .openStream()
                .use { input -> target.outputStream().use(input::copyTo) }
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        check(digest == winswSha256) { "Pinned WinSW SHA-256 mismatch" }
    }
}

tasks.named<ProcessResources>("processResources") {
    dependsOn(":windows-service:fatJar", downloadPinnedWinSw, verifyPinnedWindowsRuntime)
    from(windowsRuntimeDir) {
        include("sing-box.exe", "libcronet.dll", "LICENSE", "xray.exe", "xray.sha256", "LICENSE-Xray-core")
        into("yokhan-runtime/windows")
    }
    from(serviceFatJarFile) {
        into("gates-service")
        rename { "Gates.Service.jar" }
    }
    from(winswFile) {
        into("gates-service")
    }
    from(rootProject.file("windows-service/src/main/resources/Gates.Service.xml")) {
        into("gates-service")
    }
}

val prepareGatesNativeResources by tasks.registering(Sync::class) {
    dependsOn(":windows-service:fatJar", downloadPinnedWinSw, verifyPinnedWindowsRuntime)
    into(nativeAppResourcesRoot.map { it.dir("windows") })
    from(windowsRuntimeDir) {
        include("sing-box.exe", "libcronet.dll", "LICENSE", "xray.exe", "xray.sha256", "LICENSE-Xray-core")
        into("yokhan-runtime/windows")
    }
    from(serviceFatJarFile) {
        into("gates-service")
        rename { "Gates.Service.jar" }
    }
    from(winswFile) {
        into("gates-service")
    }
    from(rootProject.file("windows-service/src/main/resources/Gates.Service.xml")) {
        into("gates-service")
    }
}

tasks.configureEach {
    if (name == "prepareAppResources") {
        dependsOn(prepareGatesNativeResources)
    }
}

val forbiddenWindowsUiStylePatterns = listOf(
    Regex("""\bColor\s*\(\s*0x[0-9A-Fa-f_]+"""),
    Regex("""\bColor\s*\(\s*\d+\s*,"""),
    Regex("""java\.awt\.Color\s*(?:\(|\.)"""),
)

val forbiddenMojibakeMarkers = listOf(
    "\u0420\u201C",
    "\u0420\u045F",
    "\u0420\u045B",
    "\u0420\u2019",
    "\u0420\u0455",
    "\u0421\u201A",
    "\u0421\u0402",
    "\u0421\u0403",
    "\u0421\u040F",
)

fun decodeUtf8Strict(file: File): String {
    val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
}

tasks.register("checkWindowsUiUsesDesignTokens") {
    val kotlinFiles = fileTree("src/main/kotlin") {
        include("**/*.kt")
    }
    inputs.files(kotlinFiles)

    doLast {
        val offenders = kotlinFiles.files
            .sortedBy { it.path }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (forbiddenWindowsUiStylePatterns.any { it.containsMatchIn(line) }) {
                        "${file.relativeTo(projectDir)}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }

        check(offenders.isEmpty()) {
            "Windows UI must use shared app design tokens/contracts, not hard-coded colors:\n" +
                offenders.joinToString(separator = "\n")
        }
    }
}

tasks.register("checkWindowsTextEncoding") {
    val textFiles = files(
        fileTree(projectDir) {
            include("src/main/kotlin/**/*.kt")
            include("src/test/kotlin/**/*.kt")
            include("src/main/jpackage/**/*.wxs")
            include("tools/**/*.ps1")
        },
        rootProject.fileTree("windows-service") {
            include("src/**/*.kt")
            include("src/**/*.xml")
            include("*.kts")
        },
        rootProject.file(".github/workflows/release.yml"),
        rootProject.projectDir.resolve("docs/windows-product-client-spec.md"),
    )
    inputs.files(textFiles)

    doLast {
        val offenders = mutableListOf<String>()

        textFiles.files
            .filter { it.isFile }
            .sortedBy { it.path }
            .forEach { file ->
                val bytes = file.readBytes()
                val relativePath = file.relativeToOrSelf(projectDir).path

                if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
                    offenders += "$relativePath: UTF-8 BOM is not allowed"
                }

                val text = try {
                    decodeUtf8Strict(file)
                } catch (_: CharacterCodingException) {
                    offenders += "$relativePath: file must be valid UTF-8"
                    return@forEach
                }

                if (file.extension.equals("ps1", ignoreCase = true) && text.any { it.code > 127 }) {
                    offenders += "$relativePath: PowerShell scripts must stay ASCII-only for powershell.exe compatibility"
                }

                forbiddenMojibakeMarkers.firstOrNull { marker -> text.contains(marker) }?.let { marker ->
                    val lineNumber = text.lineSequence().indexOfFirst { line -> line.contains(marker) } + 1
                    offenders += "$relativePath:$lineNumber: probable mojibake marker detected"
                }
                if (text.contains('\uFFFD')) {
                    val lineNumber = text.lineSequence().indexOfFirst { line -> line.contains('\uFFFD') } + 1
                    offenders += "$relativePath:$lineNumber: Unicode replacement character detected"
                }
            }

        check(offenders.isEmpty()) {
            "Windows text files failed encoding checks:\n" + offenders.joinToString(separator = "\n")
        }
    }
}

tasks.register("checkWindowsReleaseMetadata") {
    val runtimeController = file("src/main/kotlin/com/yokhan/vpn/windows/WindowsRuntimeController.kt")
    inputs.file(runtimeController)

    doLast {
        val runtimeText = decodeUtf8Strict(runtimeController)
        check(runtimeText.contains("""private const val WINDOWS_APP_VERSION = "$windowsPackageVersion"""")) {
            "WINDOWS_APP_VERSION must match packageVersion $windowsPackageVersion"
        }
    }
}

val forbiddenHubDownloadChannelTokens = listOf("/hub", "hub/gates")

tasks.register("checkWindowsNoHubDownloadChannel") {
    description = "Fails if the Windows PowerShell client, production config, or config example reference the retired Hub download channel."

    // The canonical Kotlin constant (GATES_DOWNLOAD_PAGE_URL) and legacy URL
    // migration are verified by WindowsEndpointMigrationTest; the migration
    // function must keep the legacy /hub/gates literals as its inputs, so those
    // are intentionally excluded from this substring scan.
    val windowsClientDir = providers.environmentVariable("GATES_WINDOWS_CLIENT_DIR")
        .map { file(it) }
        .orElse(rootProject.projectDir.parentFile.parentFile.resolve("YokhanIndependentVPN").resolve("windows-client"))

    doLast {
        val offenders = mutableListOf<String>()

        val scannedFiles = listOf(
            "app-config.json.example",
            "GATES.ps1",
            "runtime/app-config.json",
        ).map { relative -> File(windowsClientDir.get(), relative) }

        scannedFiles.filter { it.isFile }
            .sortedBy { it.path }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (forbiddenHubDownloadChannelTokens.any { line.contains(it) }) {
                        offenders += "${file.path}:${index + 1}: ${line.trim()}"
                    }
                }
            }

        check(offenders.isEmpty()) {
            "Windows production config and config examples must not reference the retired Hub download channel:\n" +
                offenders.joinToString(separator = "\n")
        }
    }
}

tasks.named("check") {
    dependsOn("checkWindowsUiUsesDesignTokens")
    dependsOn("checkWindowsTextEncoding")
    dependsOn("checkWindowsReleaseMetadata")
    dependsOn("checkWindowsNoHubDownloadChannel")
}

compose.desktop {
    application {
        mainClass = "com.yokhan.gates.windows.MainKt"
        jvmArgs("-Dgates.update.signer.sha1=${windowsUpdateSignerSha1.get()}")

        nativeDistributions {
            appResourcesRootDir.set(nativeAppResourcesRoot)
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            // Gson-backed persisted Kotlin models need Unsafe in the packaged runtime too.
            modules("java.net.http", "java.management", "jdk.unsupported", "jdk.httpserver", "jdk.jfr")
            packageName = "GATES"
            packageVersion = windowsPackageVersion
            vendor = "Yokhan"
            copyright = "Copyright (c) 2026 Yokhan (Igor Nazarov)"
            description = "GATES Windows client by Yokhan (Igor Nazarov)"
            windows {
                iconFile.set(project.file("src/main/resources/branding/gates.ico"))
                perUserInstall = false
                dirChooser = false
                shortcut = true
                menu = true
                menuGroup = "GATES"
                upgradeUuid = "33d7b058-0da8-4ada-9108-d4511da47b21"
            }
        }
    }
}

val jpackageTemplateDir = layout.projectDirectory.dir("src/main/jpackage")
val wixToolsetDir = rootProject.layout.buildDirectory.dir("wix311")

fun registerSelfHealingJPackageOverride(taskName: String, packageType: String, fileExtension: String) {
    tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>(taskName) {
        dependsOn("createDistributable")
        inputs.dir(jpackageTemplateDir)
        doLast {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("win")) return@doLast
            val appImageDir = layout.buildDirectory.dir("compose/binaries/main/app/GATES").get().asFile
            val outDir = destinationDir.get().asFile
            val targetInstaller = File(outDir, "GATES-$windowsPackageVersion.$fileExtension")
            if (targetInstaller.exists()) {
                check(targetInstaller.delete()) { "Could not replace $targetInstaller" }
            }
            val jpackageExe = File(System.getProperty("java.home"), "bin/jpackage.exe")
            val existingPath = System.getenv("PATH").orEmpty()
            val wixPath = wixToolsetDir.get().asFile.absolutePath
            val mergedPath = if (existingPath.isBlank()) wixPath else "$wixPath${File.pathSeparator}$existingPath"
            val result = project.exec {
                environment("PATH", mergedPath)
                commandLine(
                    jpackageExe.absolutePath,
                    "--type", packageType,
                    "--app-image", appImageDir.absolutePath,
                    "--name", "GATES",
                    "--app-version", windowsPackageVersion,
                    "--vendor", "Yokhan",
                    "--copyright", "Copyright (c) 2026 Yokhan (Igor Nazarov)",
                    "--description", "GATES Windows client by Yokhan (Igor Nazarov)",
                    "--icon", project.file("src/main/resources/branding/gates.ico").absolutePath,
                    "--resource-dir", jpackageTemplateDir.asFile.absolutePath,
                    "--dest", outDir.absolutePath,
                    "--win-menu",
                    "--win-menu-group", "GATES",
                    "--win-shortcut",
                    "--win-upgrade-uuid", "33d7b058-0da8-4ada-9108-d4511da47b21",
                )
            }
            result.assertNormalExitValue()
            check(targetInstaller.isFile) { "Missing repackaged installer: $targetInstaller" }
        }
    }
}

afterEvaluate {
    registerSelfHealingJPackageOverride("packageMsi", "msi", "msi")

    val bootstrapperSource = layout.projectDirectory.file("src/main/installer/GatesBootstrapper.cs").asFile
    val bootstrapperManifest = layout.projectDirectory.file("src/main/installer/GatesBootstrapper.manifest").asFile
    val brandIcon = project.file("src/main/resources/branding/gates.ico")
    val msiDir = layout.buildDirectory.dir("compose/binaries/main/msi")

    tasks.named<org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask>("packageExe") {
        dependsOn("packageMsi")
        inputs.file(bootstrapperSource)
        inputs.file(bootstrapperManifest)
        inputs.file(brandIcon)
        inputs.dir(msiDir)
        doLast {
            val osName = System.getProperty("os.name").lowercase()
            if (!osName.contains("win")) return@doLast
            val msiFile = File(msiDir.get().asFile, "GATES-$windowsPackageVersion.msi")
            check(msiFile.isFile) { "Missing packaged MSI for self-healing EXE wrapper: $msiFile" }
            val outDir = destinationDir.get().asFile
            outDir.mkdirs()
            val targetInstaller = File(outDir, "GATES-$windowsPackageVersion.exe")
            if (targetInstaller.exists()) {
                check(targetInstaller.delete()) { "Could not replace $targetInstaller" }
            }
            val cscCandidates = listOf(
                File("C:\\Windows\\Microsoft.NET\\Framework64\\v4.0.30319\\csc.exe"),
                File("C:\\Windows\\Microsoft.NET\\Framework\\v4.0.30319\\csc.exe"),
            )
            val cscExe = cscCandidates.firstOrNull { it.isFile }
                ?: error("Could not find .NET Framework v4 csc.exe to build self-healing GATES EXE installer.")
            val result = project.exec {
                commandLine(
                    cscExe.absolutePath,
                    "/nologo",
                    "/target:winexe",
                    "/platform:x64",
                    "/optimize+",
                    "/utf8output",
                    "/win32icon:${brandIcon.absolutePath}",
                    "/win32manifest:${bootstrapperManifest.absolutePath}",
                    "/reference:System.dll",
                    "/reference:System.Drawing.dll",
                    "/reference:System.Windows.Forms.dll",
                    "/resource:${msiFile.absolutePath},GATES.msi",
                    "/out:${targetInstaller.absolutePath}",
                    bootstrapperSource.absolutePath,
                )
            }
            result.assertNormalExitValue()
            check(targetInstaller.isFile && targetInstaller.length() >= msiFile.length()) {
                "Self-healing EXE installer was not built properly: $targetInstaller"
            }
        }
    }
}
