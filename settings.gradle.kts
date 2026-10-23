pluginManagement {
    repositories {
        mavenLocal()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
    }
}

rootProject.name = "AsteriaDirectorate"

include(":astd-api")
project(":astd-api").projectDir = file("modules/api/astd-api")
include(":astd-api-render")
project(":astd-api-render").projectDir = file("modules/api/astd-api-render")

include(":astd-impl")
project(":astd-impl").projectDir = file("modules/internal/astd-impl")
include(":astd-ui")
project(":astd-ui").projectDir = file("modules/internal/astd-ui")
include(":astd-render")
project(":astd-render").projectDir = file("modules/internal/astd-render")
include(":astd-combat")
project(":astd-combat").projectDir = file("modules/internal/astd-combat")
include(":astd-campaign")
project(":astd-campaign").projectDir = file("modules/internal/astd-campaign")
include(":astd-automation")
project(":astd-automation").projectDir = file("modules/internal/astd-automation")
include(":astd-csv")
project(":astd-csv").projectDir = file("modules/internal/astd-csv")
