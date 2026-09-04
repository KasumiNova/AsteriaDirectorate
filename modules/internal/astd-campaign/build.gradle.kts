plugins {
    kotlin("jvm")
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-impl"))
    implementation(project(":astd-ui"))
    implementation(project(":astd-combat"))

    // ASTDDevContentSelectorTest 等使用仓库级 CSV 读取工具。
    testImplementation(testFixtures(project(":astd-csv")))
}

// 装配：向根工程 mod 主 jar 贡献本模块产物（SDG 打包单点绑定根工程 jar task）。
rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}
