//plugins {
//    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
//}
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
        }
        mavenCentral()
    }
}

rootProject.name = "AsteriaDirectorate"

include(":ss-csv")
