rootProject.name = "NB4A"
include(":app")
include(":shared-domain")
include(":shared-ui")
include(":desktop-preview")
project(":shared-domain").projectDir = file("shared/domain")
project(":shared-ui").projectDir = file("shared/ui")
project(":desktop-preview").projectDir = file("preview/desktop")
