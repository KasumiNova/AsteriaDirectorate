plugins {
    kotlin("jvm")
    // 跨模块共享测试桩（WarnCapture/stubShip/stubWeapon 等）：astd-combat / astd-render 的测试经 testFixtures 引用。
    `java-test-fixtures`
}

group = "cn.kasuminova"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":astd-api"))
    implementation(project(":astd-api-render"))

    // testFixtures 的公开桩类型（StubBuff 等）直接暴露 astd-api 的 Buff 接口，须以 api 传递。
    testFixturesApi(project(":astd-api"))

    // 纪律测试的仓库布局定位器（RepoLayout/CsvTestUtil）。
    testImplementation(testFixtures(project(":astd-csv")))
}

// 装配：向根工程 mod 主 jar 贡献本模块产物（SDG 打包单点绑定根工程 jar task）。
rootProject.tasks.named<Jar>("jar") {
    from(sourceSets.main.get().output)
    dependsOn(tasks.named("classes"))
}
