import org.jetbrains.compose.desktop.application.dsl.TargetFormat

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
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "com.yokhan.vpn.preview.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "YokhanVPNDashboardPreview"
            packageVersion = "0.1.0"
            vendor = "Yokhan"
            description = "Desktop dashboard UI preview for YokhanVPN"
        }
    }
}
