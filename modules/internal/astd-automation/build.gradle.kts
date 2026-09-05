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

// 装配：向根工程 mod 主 jar 贡献本模块产物。
// 自动化测试模块不随 release 打包：-Pastd.includeAutomation=false 时跳过（根工程 copyContents 同步门控）。
if (providers.gradleProperty("astd.includeAutomation").map(String::toBooleanStrict).orElse(true).get()) {
    rootProject.tasks.named<Jar>("jar") {
        from(sourceSets.main.get().output)
        dependsOn(tasks.named("classes"))
    }
}
