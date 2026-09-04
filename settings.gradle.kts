//plugins {
//    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
//}
pluginManagement {
    repositories {
        // SDG 插件自 mavenLocal 消费（io.github.nanoforged:sdg）
        mavenLocal()
        gradlePluginPortal()
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
    }
}

rootProject.name = "AsteriaDirectorate"

// 多模块布局：api 模块在 modules/api，实现模块在 modules/internal。
// 根工程保留为模组装配/打包工程（SDG mod 插件、contents、agent、mod 插件入口类）。
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
