plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm("desktop")

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
