rootProject.name = "NB4A"
include(":app")
include(":shared-domain")
include(":desktop-preview")
project(":shared-domain").projectDir = file("shared/domain")
project(":desktop-preview").projectDir = file("preview/desktop")
