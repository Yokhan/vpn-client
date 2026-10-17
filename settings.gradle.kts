rootProject.name = "NB4A"
include(":app")
include(":shared-domain")
include(":shared-ui")
include(":desktop-preview")
include(":windows-app")
include(":windows-service")
project(":shared-domain").projectDir = file("shared/domain")
project(":shared-ui").projectDir = file("shared/ui")
project(":desktop-preview").projectDir = file("preview/desktop")
project(":windows-app").projectDir = file("windows-app")
if (providers.gradleProperty("GATES_UI_LAB").orNull == "1") {
    include(":ui-lab")
    project(":ui-lab").projectDir = file("preview/android-ui-lab")
}
