plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-api-render"))
    implementation(project(":astd-impl"))
    implementation(project(":astd-render"))
    implementation(project(":astd-combat"))
    implementation(project(":astd-campaign"))
    implementation(project(":astd-ui"))
}

// 自动化模块不随 release 打包（-Pastd.release=true 时默认排除），可用 -Pastd.includeAutomation 显式覆盖
val astdIncludeAutomation: Boolean =
    providers.gradleProperty("astd.includeAutomation").map(String::toBooleanStrict)
        .orElse(providers.gradleProperty("astd.release").map { release -> !release.toBooleanStrict() })
        .orElse(true).get()
if (astdIncludeAutomation) {
    rootProject.tasks.named<Jar>("jar") {
        from(sourceSets.main.get().output)
        dependsOn(tasks.named("classes"))
    }
}
