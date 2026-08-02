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

include(":ss-csv")
