import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.gradle.language.jvm.tasks.ProcessResources

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
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("com.google.code.gson:gson:2.11.0")
}

val defaultRuntimeDir = rootProject.projectDir.parentFile
    .resolve("YokhanIndependentVPN")
    .resolve(".tmp")
    .resolve("sing-box")
    .resolve("sing-box-1.13.12-windows-amd64")

val windowsRuntimeDir = providers.environmentVariable("YOKHAN_SING_BOX_DIR")
    .map { file(it) }
    .orElse(defaultRuntimeDir)

tasks.named<ProcessResources>("processResources") {
    from(windowsRuntimeDir) {
        include("sing-box.exe", "libcronet.dll", "LICENSE")
        into("yokhan-runtime/windows")
    }
}

compose.desktop {
    application {
        mainClass = "com.yokhan.gates.windows.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "GATES"
            packageVersion = "1.6.45"
            vendor = "Yokhan"
            description = "GATES Windows client"
        }
    }
}
