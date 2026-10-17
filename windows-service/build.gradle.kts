plugins {
    application
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":shared-domain"))
    implementation("com.google.code.gson:gson:2.11.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.yokhan.gates.windows.service.GatesServiceMainKt")
}

tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles the runnable GATES Windows service JAR."
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = application.mainClass
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get().map { dependency ->
            if (dependency.isDirectory) dependency else zipTree(dependency)
        }
    })
}
